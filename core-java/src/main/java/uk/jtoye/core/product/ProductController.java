package uk.jtoye.core.product;

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
import org.springframework.format.annotation.DateTimeFormat;
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
import uk.jtoye.core.product.dto.CopyProductsRequest;
import uk.jtoye.core.product.dto.CopyProductsResult;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.product.dto.CulinaryPackDto;
import uk.jtoye.core.product.dto.CulinaryPackSeedRequest;
import uk.jtoye.core.product.dto.CulinaryPackSeedResult;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.product.dto.ProductPatchRequest;

import java.net.URI;
import java.time.LocalDate;
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
    private final CulinaryPackSeedService culinaryPackSeedService;

    public ProductController(ProductService productService, ProductLabelService labelService,
                              ImageAnalysisService imageAnalysisService, BulkImportService bulkImportService) {
        this(productService, labelService, imageAnalysisService, bulkImportService, null);
    }

    @Autowired
    public ProductController(ProductService productService, ProductLabelService labelService,
                              ImageAnalysisService imageAnalysisService, BulkImportService bulkImportService,
                              @jakarta.annotation.Nullable CulinaryPackSeedService culinaryPackSeedService) {
        this.productService = productService;
        this.labelService = labelService;
        this.imageAnalysisService = imageAnalysisService;
        this.bulkImportService = bulkImportService;
        this.culinaryPackSeedService = culinaryPackSeedService;
    }

    @GetMapping
    @Operation(summary = "List products", description = "Returns a paginated list of products for the authenticated tenant. All products include mandatory allergen and ingredient information per Natasha's Law. Optional shopId query param narrows to one shop of the tenant, server-side (requires at least STAFF on that shop). Optional availableOnly=true lists only products on sale, filtered in the query so totalElements counts only those. Each product carries allergens, its declared allergens in words, beside allergenMask.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successfully retrieved products"),
            @ApiResponse(responseCode = "401", description = "Unauthorized - missing or invalid JWT"),
            @ApiResponse(responseCode = "403", description = "Caller has no grant on the requested shopId")
    })
    public Page<ProductDto> list(
            @Parameter(description = "Optional shop to narrow the list to")
            @RequestParam(required = false) UUID shopId,
            @Parameter(description = "Optional: true lists only products on sale (available). Absent or false lists every product, as before.")
            @RequestParam(required = false) Boolean availableOnly,
            @Parameter(description = "Pagination parameters", hidden = true)
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        // RLS ensures we only see current tenant rows; shopId narrows within the tenant
        // at the QUERY (WR-04 / issue #280) rather than in the browser over one page.
        // 37-42 (UXT-040): availableOnly narrows at the query too, so the MCP list_products stays a forwarder.
        boolean onSaleOnly = Boolean.TRUE.equals(availableOnly);
        if (shopId != null) {
            return onSaleOnly
                    ? productService.getAvailableProductsByShop(shopId, pageable)
                    : productService.getProductsByShop(shopId, pageable);
        }
        return onSaleOnly
                ? productService.getAvailableProducts(pageable)
                : productService.getAllProducts(pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get product by ID", description = "Returns a single product by ID for the authenticated tenant")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Product found"),
            // API-5: a malformed id is a live 400 this operation never declared - a generated
            // client had no typed branch for the commonest bad request against it.
            @ApiResponse(responseCode = "400",
                    description = "The id path variable is not a UUID (errors/type-mismatch)"),
            @ApiResponse(responseCode = "401",
                    description = "No bearer token, or one that is expired or invalid (errors/unauthorized)"),
            @ApiResponse(responseCode = "403",
                    description = "Caller holds no STAFF grant on the shop that owns this product "
                            + "(errors/shop-access-denied)"),
            @ApiResponse(responseCode = "404", description = "Product not found (errors/not-found)")
    })
    public ResponseEntity<ProductDto> getById(
            @Parameter(description = "Product ID") @PathVariable UUID id) {
        return productService.getProductById(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
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
    @Operation(summary = "Bulk import from CSV",
            description = "Import multiple products from a CSV file. Returns created products and per-row errors. "
                    + "Each row is created in the shop its shop_id cell names, or else in the shop given by the "
                    + "shopId parameter. A row with neither is reported as the per-row error 'shop required' and "
                    + "is not created (a product with no shop is never shown on a storefront). A row naming a shop "
                    + "the caller does not manage is a per-row error and is not created; the other rows still import.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Import processed; see created and errors"),
            @ApiResponse(responseCode = "401",
                    description = "No bearer token, or one that is expired or invalid (errors/unauthorized)"),
            @ApiResponse(responseCode = "403",
                    description = "Token lacks the catalog:write scope (errors/forbidden), or the caller holds no "
                            + "SHOP_MANAGER grant on shopId (errors/shop-access-denied); nothing is written")
    })
    public ResponseEntity<BulkImportResult> bulkImportCsv(
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "Shop to import into: rows without their own shop_id are created here. "
                    + "Requires SHOP_MANAGER on this shop.")
            @RequestParam(required = false) UUID shopId) {
        BulkImportResult result = bulkImportService.importFromCsv(file, shopId);
        return ResponseEntity.ok(result);
    }

    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")  // issue #206 [AI-4]: catalog write scope
    @PostMapping(value = "/bulk/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Bulk import from images", description = "Upload multiple food photos. AI identifies each item and creates draft products.")
    public ResponseEntity<BulkImportResult> bulkImportImages(@RequestParam("files") MultipartFile[] files) {
        BulkImportResult result = bulkImportService.importFromImages(files);
        return ResponseEntity.ok(result);
    }

    // ---- Copy to shop (37-13, RWO-026 / UXT-026) ----

    // Agent-readiness: no Idempotency-Key, because the operation is provably idempotent (a product the
    // target already has is skipped, under a target-shop lock), and no MCP tool (catalogue bulk operations
    // are a dashboard task; recorded in 37-13).
    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")  // issue #206 [AI-4]: catalog write scope
    @PostMapping(value = "/copy", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Copy products to another shop",
            description = "Copies products from one shop of the tenant to another through the normal product "
                    + "create path. Allergen data (declared mask, emphasis spans, may-contain mask, ingredients), "
                    + "price, VAT rate, label fields, stock and photos are carried verbatim; photos are shared "
                    + "(new links to the same images, nothing duplicated). Omit productIds to copy the whole "
                    + "menu. Idempotent: a product the target already has (its copy SKU, or the same title "
                    + "ignoring case) is SKIPPED, so repeating a copy creates nothing. A requested product that "
                    + "is not in the source shop is a FAILED item; the others still copy. Each copy gets the "
                    + "SKU {source SKU}-{first 8 characters of the target shop id}, because SKUs are unique "
                    + "per tenant.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Copy processed; see created, skipped, failed and items"),
            @ApiResponse(responseCode = "400",
                    description = "Missing shop id, more than 500 productIds, or source and target are the same "
                            + "shop (errors/validation, errors/invalid-argument)"),
            @ApiResponse(responseCode = "401",
                    description = "No bearer token, or one that is expired or invalid (errors/unauthorized)"),
            @ApiResponse(responseCode = "403",
                    description = "Token lacks the catalog:write scope (errors/forbidden), or the caller holds no "
                            + "SHOP_MANAGER grant on the source or the target shop (errors/shop-access-denied)"),
            @ApiResponse(responseCode = "404", description = "A shop is not one of the caller's tenant (errors/not-found)")
    })
    public ResponseEntity<CopyProductsResult> copyToShop(@Valid @RequestBody CopyProductsRequest request) {
        return ResponseEntity.ok(productService.copyProducts(request));
    }

    // ---- Culinary Knowledge Base 1-Click Seeding (GTM-BLK-02) ----

    @GetMapping("/culinary-packs")
    @Operation(summary = "List Pan-African & Caribbean culinary packs",
            description = "Returns the 10 pre-audited regional cuisine packs (52 dishes) from the bundled OaaS "
                    + "Culinary Knowledge Base (GTM-BLK-02), with UK FSA 14-allergen declared and cross-contact "
                    + "bitmasks, inline-emphasised ingredients prose, and recommended refrigerated shelf-life days.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Available culinary packs and dish templates"),
            @ApiResponse(responseCode = "401", description = "No bearer token, or an invalid one")
    })
    public ResponseEntity<List<CulinaryPackDto>> listCulinaryPacks() {
        if (culinaryPackSeedService == null) {
            throw new IllegalStateException("Culinary pack seed service is not configured");
        }
        return ResponseEntity.ok(culinaryPackSeedService.listPacks());
    }

    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")
    @PostMapping(value = "/culinary-packs/seed", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Seed shop catalogue from Pan-African & Caribbean culinary pack",
            description = "Idempotently seeds a shop's product catalogue from one or more regional cuisine packs "
                    + "or specific dish template slugs (GTM-BLK-02). Requires SHOP_MANAGER on shopId and "
                    + "SCOPE_catalog:write. Seeded dishes are created as drafts (available=false) by default so the "
                    + "Food Business Operator confirms their exact kitchen recipe and price before publishing. "
                    + "Dishes whose deterministic CKB SKU or title already exist in the target shop are SKIPPED.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Seed processed; see created, skipped, failed and items"),
            @ApiResponse(responseCode = "400",
                    description = "Missing shopId, or neither cuisineKeys nor dishSlugs provided (errors/validation, errors/invalid-argument)"),
            @ApiResponse(responseCode = "401",
                    description = "No bearer token, or one that is expired or invalid (errors/unauthorized)"),
            @ApiResponse(responseCode = "403",
                    description = "Token lacks the catalog:write scope (errors/forbidden), or the caller holds no "
                            + "SHOP_MANAGER grant on shopId (errors/shop-access-denied)"),
            @ApiResponse(responseCode = "404", description = "Shop not found in the caller's tenant (errors/not-found)")
    })
    public ResponseEntity<CulinaryPackSeedResult> seedCulinaryPack(
            @Valid @RequestBody CulinaryPackSeedRequest request) {
        if (culinaryPackSeedService == null) {
            throw new IllegalStateException("Culinary pack seed service is not configured");
        }
        return ResponseEntity.ok(culinaryPackSeedService.seedPack(request));
    }

    // ---- Labels ----

    public ResponseEntity<byte[]> generateLabel(UUID id, LocalDate productionDate) {
        return generateLabel(id, productionDate, null);
    }

    @GetMapping("/{id}/label")
    @Operation(summary = "Generate allergen label PDF",
            description = "Returns a PPDS (Natasha's Law) allergen label PDF for the product. The label "
                    + "prints the production date and a use-by / best-before date counted from it "
                    + "(production date + the product's shelf life), and a separate 'May contain' line "
                    + "for cross-contact allergens that are not already declared. Dates are printed "
                    + "en-GB, e.g. '5 October 2026'. Optional format parameter selects STANDARD_100X60MM "
                    + "(100x60mm sticker, default) or THERMAL_62MM (62mm continuous thermal roll for "
                    + "Brother QL-800/810W printers, GTM-BLK-06).")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The label PDF (attachment label-{id}.pdf)"),
            @ApiResponse(responseCode = "400", description = "Unsupported label format parameter (errors/invalid-argument)"),
            @ApiResponse(responseCode = "401", description = "No bearer token, or an invalid one"),
            @ApiResponse(responseCode = "403",
                    description = "The caller holds no STAFF grant on the product's shop (errors/shop-access-denied)"),
            @ApiResponse(responseCode = "404", description = "Product not found"),
            @ApiResponse(responseCode = "422",
                    description = "The product lacks required label data (errors/incomplete-label-data), "
                            + "or productionDate is after today (UK time) or gives a durability date "
                            + "that has already passed (errors/invalid-production-date, field "
                            + "productionDate)")
    })
    public ResponseEntity<byte[]> generateLabel(
            @PathVariable UUID id,
            @Parameter(description = "The date the food was produced (ISO yyyy-MM-dd). Use-by / "
                    + "best-before is counted from it. Defaults to today in Europe/London. Must not be "
                    + "after today, and must not give a durability date that has already passed.",
                    example = "2026-10-03")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate productionDate,
            @Parameter(description = "Optional page layout: STANDARD_100X60MM (default 100x60mm sheet/sticker) "
                    + "or THERMAL_62MM (62mm continuous roll for Brother QL thermal printers, GTM-BLK-06).",
                    example = "THERMAL_62MM")
            @RequestParam(required = false) String format) {
        ProductLabelService.LabelFormat resolvedFormat = ProductLabelService.LabelFormat.fromQueryParam(format);
        byte[] pdf = labelService.generateLabel(id, productionDate, resolvedFormat);
        String suffix = resolvedFormat == ProductLabelService.LabelFormat.THERMAL_62MM ? "-62mm" : "";
        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition", "attachment; filename=label-" + id + suffix + ".pdf")
                .body(pdf);
    }

    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")  // issue #206 [AI-4]: catalog write scope
    @PostMapping
    @Operation(summary = "Create product", description = "Creates a new product. Requires ingredients_text, allergen_mask, and price per Natasha's Law (UK) and business requirements.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Product created successfully"),
            @ApiResponse(responseCode = "400", description = "Validation error - missing required fields or invalid price"),
            // API-5: the scope gate above and the shop-grant check in ProductService both
            // answer 403 live, and neither was declared.
            @ApiResponse(responseCode = "401",
                    description = "No bearer token, or one that is expired or invalid (errors/unauthorized)"),
            @ApiResponse(responseCode = "403",
                    description = "Token lacks the catalog:write scope (errors/forbidden), or the caller "
                            + "holds no SHOP_MANAGER grant on the target shop (errors/shop-access-denied)"),
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
        // issue #500: no local catch — GlobalExceptionHandler owns the RFC 7807 404 body.
        return ResponseEntity.ok(productService.updateProduct(id, req));
    }

    @PreAuthorize("hasAuthority('SCOPE_catalog:write')")  // 37-39: the same scope as PUT
    @PatchMapping("/{id}")
    @Operation(summary = "Patch product stock and availability",
            description = "Changes only available, quantityInStock and trackInventory, for EPOS and agent integrations "
                    + "that must never touch allergen or ingredient data. Any other property is refused with a 400 "
                    + "naming it and nothing is changed. Omitted fields keep their stored value; values are "
                    + "absolute, so repeating the same PATCH is idempotent. Requires SHOP_MANAGER on the product's "
                    + "shop, like PUT.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Product patched; the full product is returned"),
            @ApiResponse(responseCode = "400",
                    description = "errors/validation: a property other than available, quantityInStock and "
                            + "trackInventory (named in errors), trackInventory true without quantityInStock or "
                            + "false with one, or a negative quantityInStock; errors/unreadable-request: the body is "
                            + "not JSON"),
            @ApiResponse(responseCode = "401",
                    description = "No bearer token, or one that is expired or invalid (errors/unauthorized)"),
            @ApiResponse(responseCode = "403",
                    description = "Token lacks the catalog:write scope (errors/forbidden), or the caller holds no "
                            + "SHOP_MANAGER grant on the product's shop (errors/shop-access-denied)"),
            @ApiResponse(responseCode = "404", description = "Product not found (errors/not-found)"),
            @ApiResponse(responseCode = "409",
                    description = "The product changed concurrently; re-read and retry (errors/concurrent-modification)")
    })
    public ResponseEntity<ProductDto> patch(
            @Parameter(description = "Product ID") @PathVariable UUID id,
            @Valid @RequestBody ProductPatchRequest req) {
        return ResponseEntity.ok(productService.patchProduct(id, req));
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
        // issue #500: see update() above.
        productService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }
}
