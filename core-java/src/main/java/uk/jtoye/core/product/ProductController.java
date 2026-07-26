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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import uk.jtoye.core.ai.ImageAnalysisResult;
import uk.jtoye.core.ai.ImageAnalysisService;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.product.dto.BulkImportResult;
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
    private final BulkImportService bulkImportService;

    public ProductController(ProductService productService, ProductLabelService labelService,
                              ImageAnalysisService imageAnalysisService, BulkImportService bulkImportService) {
        this.productService = productService;
        this.labelService = labelService;
        this.imageAnalysisService = imageAnalysisService;
        this.bulkImportService = bulkImportService;
    }

    @GetMapping
    @Operation(summary = "List products", description = "Returns a paginated list of products for the authenticated tenant. All products include mandatory allergen and ingredient information per Natasha's Law. Optional shopId query param narrows to one shop of the tenant, server-side (requires at least STAFF on that shop).")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved products"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - missing or invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Caller has no grant on the requested shopId")
    })
    public Page<ProductDto> list(
            @Parameter(description = "Optional shop to narrow the list to")
            @RequestParam(required = false) UUID shopId,
            @Parameter(description = "Pagination parameters", hidden = true)
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        // RLS ensures we only see current tenant rows; shopId narrows within the tenant
        // at the QUERY (WR-04 / issue #280) rather than in the browser over one page.
        return shopId != null
                ? productService.getProductsByShop(shopId, pageable)
                : productService.getAllProducts(pageable);
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
    @Operation(summary = "Search products",
            description = "Full-text search (GIN/tsvector, per-word prefix matching, relevance-ranked) over "
                    + "title, category, description, ingredients and dietary tags, plus SKU prefix lookup. "
                    + "Returns a JSON array (wire contract frozen: edge WhatsApp flow and dashboard both "
                    + "consume it) of at most one page; optional page/size params paginate, with size "
                    + "capped by the global pageable maximum (100). Optional shopId narrows to one "
                    + "shop of the tenant (requires at least STAFF on that shop).")
    public List<ProductDto> search(
            @RequestParam String q,
            @Parameter(description = "Optional shop to narrow the search to")
            @RequestParam(required = false) UUID shopId,
            @Parameter(description = "Pagination parameters", hidden = true)
            @PageableDefault(size = 100) Pageable pageable) {
        // WR-04 (#280): the dashboard swaps to this endpoint at searchQuery.length >= 2, so
        // without shopId the shop switcher would silently stop applying mid-typing. The array
        // return shape is deliberately untouched — the wire contract above stays frozen.
        return productService.search(q, shopId, pageable).getContent();
    }

    // ---- Bulk Import ----

    @GetMapping("/template")
    @Operation(summary = "Download CSV template", description = "Returns a CSV template file for bulk product import")
    public ResponseEntity<byte[]> downloadTemplate() {
        byte[] csv = bulkImportService.generateCsvTemplate().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv")
                .header("Content-Disposition", "attachment; filename=product-import-template.csv")
                .body(csv);
    }

    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")  // issue #206 [AI-4]: catalog write scope
    @PostMapping(value = "/bulk/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Bulk import from CSV", description = "Import multiple products from a CSV file. Returns created products and per-row errors.")
    public ResponseEntity<BulkImportResult> bulkImportCsv(@RequestParam("file") MultipartFile file) {
        BulkImportResult result = bulkImportService.importFromCsv(file);
        return ResponseEntity.ok(result);
    }

    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")  // issue #206 [AI-4]: catalog write scope
    @PostMapping(value = "/bulk/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Bulk import from images", description = "Upload multiple food photos. AI identifies each item and creates draft products.")
    public ResponseEntity<BulkImportResult> bulkImportImages(@RequestParam("files") MultipartFile[] files) {
        BulkImportResult result = bulkImportService.importFromImages(files);
        return ResponseEntity.ok(result);
    }

    // ---- Labels ----

    @GetMapping("/{id}/label")
    @Operation(summary = "Generate allergen label PDF", description = "Returns a PDF allergen label for the product")
    public ResponseEntity<byte[]> generateLabel(@PathVariable UUID id) {
        byte[] pdf = labelService.generateLabel(id);
        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition", "attachment; filename=label-" + id + ".pdf")
                .body(pdf);
    }

    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")  // issue #206 [AI-4]: catalog write scope
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
        // issue #97 [P2-6]: inherit the WebConfig /api/v1-prefixed request path so Location resolves
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(dto.getId())
                .toUri();
        return ResponseEntity.created(location).body(dto);
    }

    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")  // issue #206 [AI-4]: catalog write scope
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

    // NOTE (Phase 24 / 24-03): the synchronous `POST /{id}/image` upload+AI-suggest handler
    // that used to live here has been RETIRED. That route (auto-prefixed to
    // POST /api/v1/products/{id}/image) is now the SOLE property of the async
    // MediaUploadController.accept (reject-early 413 + Idempotency-Key + 202) — leaving both
    // handlers on the identical {method,path,consumes} tuple would fail context refresh with
    // an "Ambiguous mapping" IllegalStateException. The non-saving AI helper below
    // (POST /{id}/image/analyze) is a DIFFERENT route and is preserved so the uploader can
    // still fetch AI suggestions (Incremental Betterment).

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

    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")  // issue #206 [AI-4]: catalog write scope
    @PostMapping(value = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Add additional product image", description = "Adds an additional image to the product gallery")
    public ResponseEntity<ProductDto> addAdditionalImage(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file) {
        ProductDto dto = productService.addAdditionalImage(id, file);
        return ResponseEntity.ok(dto);
    }

    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")  // issue #206 [AI-4]: catalog write scope
    @DeleteMapping("/{id}/images/{index}")
    @Operation(summary = "Remove additional product image", description = "Removes an additional image by index")
    public ResponseEntity<ProductDto> removeAdditionalImage(
            @PathVariable UUID id,
            @PathVariable int index) {
        ProductDto dto = productService.removeAdditionalImage(id, index);
        return ResponseEntity.ok(dto);
    }

    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")  // issue #206 [AI-4]: catalog write scope
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

    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")  // issue #206 [AI-4]: catalog write scope
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
