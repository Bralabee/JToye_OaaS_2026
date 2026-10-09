package uk.jtoye.core.product;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import uk.jtoye.core.ai.ImageAnalysisResult;
import uk.jtoye.core.ai.ImageAnalysisService;
import uk.jtoye.core.exception.ShopAccessDeniedException;
import uk.jtoye.core.media.MediaAssetService;
import uk.jtoye.core.product.dto.BulkImportResult;
import uk.jtoye.core.product.dto.BulkImportResult.RowError;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.storage.StorageService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
@Transactional
public class BulkImportService {
    private static final Logger log = LoggerFactory.getLogger(BulkImportService.class);

    /** Per-row error for a row with no shop_id cell imported without a shopId (37-13, UXT-026). */
    static final String SHOP_REQUIRED = "shop required";

    /** Per-row error for a row naming a shop the caller does not manage, or one it cannot see. */
    static final String SHOP_NOT_MANAGED = "You do not manage this shop (SHOP_MANAGER required)";

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;
    private final ImageAnalysisService imageAnalysisService;
    private final StorageService storageService;
    private final ShopAccessService shopAccessService;
    private final MediaAssetService mediaAssetService;

    public BulkImportService(ProductRepository productRepository, ProductMapper productMapper,
                              ImageAnalysisService imageAnalysisService, StorageService storageService,
                              ShopAccessService shopAccessService, MediaAssetService mediaAssetService) {
        this.productRepository = productRepository;
        this.productMapper = productMapper;
        this.imageAnalysisService = imageAnalysisService;
        this.storageService = storageService;
        this.shopAccessService = shopAccessService;
        this.mediaAssetService = mediaAssetService;
    }

    /**
     * Generate a CSV template with headers and an example row.
     */
    public String generateCsvTemplate() {
        // shop_id is optional (37-13, UXT-026): a row may name a shop the caller manages;
        // a blank cell takes the shop the import was started in (the shopId parameter).
        // A row with neither is reported as "shop required" and is not created: the
        // storefront shows products strictly by shop, so a shop-less product is invisible.
        return """
                title,sku,price_pounds,ingredients,category,description,dietary_tags,prep_time_minutes,allergen_mask,shop_id
                Jollof Rice,JOLLOF-001,8.99,"Rice, tomatoes, peppers, onions, vegetable oil, seasoning",Mains,Our signature smoky jollof rice slow-cooked to perfection,"Halal, Gluten-Free",20,0,
                Puff Puff,PUFF-001,2.50,"Flour, sugar, yeast, water, nutmeg",Snacks,Fluffy Nigerian doughnut balls lightly dusted with sugar,Vegetarian,10,1,
                """;
    }

    /**
     * Parse and import products from a CSV file.
     * Validates each row — creates valid products, collects errors for invalid rows.
     *
     * <p><strong>Target shop (37-13, UXT-026).</strong> Each row is created in the shop its
     * {@code shop_id} cell names, else in {@code defaultShopId} (the shop the vendor imported
     * into, gated SHOP_MANAGER before any row is read). A row with neither is the per-row error
     * {@value #SHOP_REQUIRED}, for a Group admin too: the storefront lists products strictly by
     * shop, so a shop-less row would be an invisible menu reported as a success.
     *
     * <p><strong>No cache eviction (issue #287).</strong> This path is create-only: every
     * row becomes a {@code new Product()} saved under a freshly generated id, and the
     * {@code products} cache region holds exactly one thing —
     * {@link ProductService.ProductCacheLoader#getProductById} keyed
     * {@code tenant:{tenantId}:getProductById:{productId}}. A row that did not previously
     * exist has no key in that region, so there is nothing an import can make stale.
     * (Previously {@code @CacheEvict(value = "products", allEntries = true)}, which clears
     * the WHOLE shared region — tenant isolation lives in the KEY, not the region — so one
     * vendor's import cold-started every other vendor's catalogue reads. Removed for the
     * same reason Phase 23 removed it from {@link ProductService#createProduct} and
     * {@code ShopService.createShop}.)
     *
     * <p>If this path ever gains an UPDATE (e.g. upsert-by-SKU) or a delete, it MUST evict
     * the affected ids through {@code TenantCacheEvictor.evictEntity("products",
     * "getProductById", id)} — the tenant-scoped, exact-key mechanism the update/delete
     * paths already use. Do NOT reach back for {@code allEntries = true}.
     */
    public BulkImportResult importFromCsv(MultipartFile file, UUID defaultShopId) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        // 37-13 (UXT-026, T-37-35): the shop the import was started in must be one the caller
        // manages. Gated BEFORE any row is read, so a refused default writes nothing and answers
        // the typed 403 (errors/shop-access-denied).
        if (defaultShopId != null) {
            shopAccessService.require(defaultShopId, ShopRole.SHOP_MANAGER);
        }

        BulkImportResult result = new BulkImportResult();
        List<String[]> rows = parseCsv(file);

        if (rows.isEmpty()) {
            result.setTotalRows(0);
            return result;
        }

        // First row is header — validate it
        String[] header = rows.get(0);
        Map<String, Integer> columnIndex = mapColumns(header);

        if (!columnIndex.containsKey("title") || !columnIndex.containsKey("price_pounds")) {
            result.getErrors().add(new RowError(1, "header", "CSV must have at least 'title' and 'price_pounds' columns"));
            result.setTotalRows(0);
            result.setErrorCount(1);
            return result;
        }

        List<String[]> dataRows = rows.subList(1, rows.size());
        result.setTotalRows(dataRows.size());

        for (int i = 0; i < dataRows.size(); i++) {
            int rowNum = i + 2; // 1-indexed, skip header
            String[] row = dataRows.get(i);

            // WR-07 (plan 23-10): a malformed shop_id CELL is bad DATA, not an
            // authorization denial. Parse it defensively — a non-UUID value becomes a
            // per-row validation error and the row is SKIPPED while the rest of the batch
            // still imports, NOT a ShopAccessDeniedException (which would tell a legitimate
            // importer they lack access they actually hold, and break the RFC 7807
            // machine-parseable-error contract a client keys on for /shop-access-denied).
            UUID rowShopId;
            try {
                rowShopId = parseShopId(row, columnIndex);
            } catch (IllegalArgumentException badId) {
                result.getErrors().add(new RowError(rowNum, "shop_id", badId.getMessage()));
                continue;
            }

            // 37-13 (UXT-026): the row's own shop, else the shop the import was started in.
            // Neither is a per-row "shop required": a shop-less product never reaches a
            // storefront (UIX-05), so creating one would report success for an invisible menu.
            UUID targetShopId = rowShopId != null ? rowShopId : defaultShopId;
            if (targetShopId == null) {
                result.getErrors().add(new RowError(rowNum, "shop_id", SHOP_REQUIRED));
                continue;
            }

            // T-37-35: a row naming a shop the caller does not manage is refused as DATA — the
            // row is not written and the rest of the batch still imports. permits() rather than
            // catching require(): an exception out of the @Transactional ShopAccessService
            // would mark this import's transaction rollback-only. A shop of another tenant gets
            // the same message, so the error never discloses that the shop exists.
            if (!targetShopId.equals(defaultShopId)
                    && !shopAccessService.permits(targetShopId, ShopRole.SHOP_MANAGER)) {
                result.getErrors().add(new RowError(rowNum, "shop_id", SHOP_NOT_MANAGED));
                continue;
            }

            try {
                Product product = parseRow(row, columnIndex, tenantId, rowNum, result);
                if (product != null) {
                    product.setShopId(targetShopId);
                    product = productRepository.save(product);
                    result.getCreated().add(productMapper.toDto(product));
                }
            } catch (Exception e) {
                result.getErrors().add(new RowError(rowNum, "row", e.getMessage()));
            }
        }

        result.setSuccessCount(result.getCreated().size());
        result.setErrorCount(result.getErrors().size());

        log.info("CSV bulk import: {} created, {} errors out of {} rows",
                result.getSuccessCount(), result.getErrorCount(), result.getTotalRows());

        return result;
    }

    /**
     * Import products from multiple images using AI analysis.
     * Each image is analyzed, then a product is created from the AI suggestions.
     *
     * <p><strong>No cache eviction (issue #287)</strong> — same reasoning as
     * {@link #importFromCsv}: create-only, so no pre-existing cache key can be staled.
     * The blast was worse here, because the old {@code @CacheEvict(allEntries = true)}
     * fired on the normal return even when AI is disabled and the method creates nothing
     * at all. The media the worker attaches asynchronously is resolved OUTSIDE the
     * {@code @Cacheable} loader (see {@code ProductService.resolveDetail}), so a
     * {@code PENDING -> ACTIVE} flip needs no eviction either.
     */
    public BulkImportResult importFromImages(MultipartFile[] files) {
        // §3-FLAG #1 (VSA-02): the AI image-import path assigns NO shop_id (products are
        // created as unassigned drafts), so there is no per-row shop to scope against.
        // Restrict it to GROUP_ADMIN (deny-by-default) rather than let a scoped user
        // create ungated tenant-wide drafts.
        shopAccessService.requireGroupAdmin();

        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

        BulkImportResult result = new BulkImportResult();
        result.setTotalRows(files.length);

        if (!imageAnalysisService.isEnabled()) {
            result.getErrors().add(new RowError(0, "ai", "AI analysis is not available. Set up Ollama or Anthropic API key."));
            result.setErrorCount(1);
            return result;
        }

        for (int i = 0; i < files.length; i++) {
            MultipartFile file = files[i];
            int rowNum = i + 1;

            try {
                byte[] imageBytes = file.getBytes();
                String contentType = file.getContentType();

                // AI analysis
                Optional<ImageAnalysisResult> analysis = imageAnalysisService.analyze(imageBytes, contentType);

                if (analysis.isEmpty() || analysis.get().getConfidence() == null || analysis.get().getConfidence() < 0.3) {
                    result.getErrors().add(new RowError(rowNum, file.getOriginalFilename(),
                            "Could not identify food item in image" + (analysis.map(a -> " (confidence: " + a.getConfidence() + ")").orElse(""))));
                    continue;
                }

                ImageAnalysisResult ai = analysis.get();

                // Create product from AI suggestions
                Product product = new Product();
                product.setTenantId(tenantId);
                product.setTitle(ai.getIdentifiedName());
                product.setSku(generateSku(ai.getIdentifiedName()));
                product.setDescription(ai.getDescription());
                product.setIngredientsText(ai.getIngredients() != null ? ai.getIngredients() : "See packaging");
                product.setCategory(ai.getCategory());
                product.setDietaryTags(ai.getDietaryTags() != null ? String.join(", ", ai.getDietaryTags()) : null);
                product.setAllergenMask(0); // Vendor must confirm allergens manually
                product.setPricePennies(0L); // Vendor must set price
                product.setAvailable(false); // Draft — vendor must review and publish
                product.setFeatured(false);
                product.setDisplayOrder(i);

                product = productRepository.save(product);

                // IMG-02 "one pipeline": route the image through the SAME safe async path as a
                // single upload — quarantine + PENDING media_asset + media_event_outbox — instead
                // of a second synchronous storageService.upload route. The worker normalizes it to
                // a WebP derivative and repoints the product_media primary slot on ACTIVE (D-04a).
                // (AI product-suggestion analysis above is preserved — only the STORAGE path is unified.)
                String sha256 = sha256Hex(imageBytes);
                mediaAssetService.acceptQuarantineAndQueue(product.getId(), imageBytes, sha256, null,
                        new MediaAssetService.MediaPlacement(true, 0));

                result.getCreated().add(productMapper.toDto(product));

                log.info("AI import: created '{}' from image {} (confidence: {}) — image queued for normalization",
                        ai.getIdentifiedName(), file.getOriginalFilename(), ai.getConfidence());

            } catch (Exception e) {
                result.getErrors().add(new RowError(rowNum, file.getOriginalFilename(), e.getMessage()));
                log.error("Failed to import image {}: {}", file.getOriginalFilename(), e.getMessage());
            }
        }

        result.setSuccessCount(result.getCreated().size());
        result.setErrorCount(result.getErrors().size());

        log.info("Image bulk import: {} created, {} errors out of {} images",
                result.getSuccessCount(), result.getErrorCount(), result.getTotalRows());

        return result;
    }

    // ---- CSV Parsing ----

    private List<String[]> parseCsv(MultipartFile file) {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) continue;
                rows.add(parseCsvLine(line));
            }
        } catch (Exception e) {
            log.error("Failed to parse CSV: {}", e.getMessage());
        }
        return rows;
    }

    /**
     * Parse a CSV line respecting quoted fields (handles commas inside quotes).
     */
    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++; // Skip escaped quote
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString().strip());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().strip());
        return fields.toArray(new String[0]);
    }

    private Map<String, Integer> mapColumns(String[] header) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) {
            map.put(header[i].strip().toLowerCase().replace(" ", "_"), i);
        }
        return map;
    }

    private Product parseRow(String[] row, Map<String, Integer> cols, UUID tenantId, int rowNum, BulkImportResult result) {
        String title = getField(row, cols, "title");
        if (title == null || title.isBlank()) {
            result.getErrors().add(new RowError(rowNum, "title", "Title is required"));
            return null;
        }

        String priceStr = getField(row, cols, "price_pounds");
        long pricePennies;
        try {
            pricePennies = Math.round(Double.parseDouble(priceStr) * 100);
        } catch (Exception e) {
            result.getErrors().add(new RowError(rowNum, "price_pounds", "Invalid price: " + priceStr));
            return null;
        }

        Product product = new Product();
        product.setTenantId(tenantId);
        product.setTitle(title);
        product.setSku(getFieldOrDefault(row, cols, "sku", generateSku(title)));
        product.setPricePennies(pricePennies);
        product.setIngredientsText(getFieldOrDefault(row, cols, "ingredients", "See product"));
        product.setCategory(getField(row, cols, "category"));
        product.setDescription(getField(row, cols, "description"));
        product.setDietaryTags(getField(row, cols, "dietary_tags"));
        product.setAvailable(true);
        product.setFeatured(false);
        product.setDisplayOrder(0);

        String prepTime = getField(row, cols, "prep_time_minutes");
        if (prepTime != null && !prepTime.isBlank()) {
            try {
                product.setPreparationTimeMinutes(Integer.parseInt(prepTime));
            } catch (NumberFormatException e) {
                // Ignore invalid prep time
            }
        }

        String allergenStr = getField(row, cols, "allergen_mask");
        if (allergenStr != null && !allergenStr.isBlank()) {
            try {
                product.setAllergenMask(Integer.parseInt(allergenStr));
            } catch (NumberFormatException e) {
                product.setAllergenMask(0);
            }
        } else {
            product.setAllergenMask(0);
        }

        return product;
    }

    /**
     * Parse the optional {@code shop_id} column for a row into a {@link UUID}, or
     * {@code null} when the column is absent/blank.
     *
     * <p>WR-07 (plan 23-10): a malformed (non-UUID) {@code shop_id} is BAD DATA, not an
     * authorization problem, so this throws {@link IllegalArgumentException} — which
     * {@code importFromCsv} catches and records as a per-row validation error (the row is
     * skipped, the batch continues). It deliberately does NOT throw
     * {@link ShopAccessDeniedException}: reporting a data typo as a 403 tells the importer
     * they lack access they actually hold and breaks the machine-parseable-error contract
     * (a client keying on {@code /shop-access-denied} would prompt for access it already has).
     */
    private UUID parseShopId(String[] row, Map<String, Integer> cols) {
        String raw = getField(row, cols, "shop_id");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid shop_id: " + raw.strip());
        }
    }

    private String getField(String[] row, Map<String, Integer> cols, String field) {
        Integer idx = cols.get(field);
        if (idx == null || idx >= row.length) return null;
        String val = row[idx].strip();
        return val.isEmpty() ? null : val;
    }

    private String getFieldOrDefault(String[] row, Map<String, Integer> cols, String field, String defaultValue) {
        String val = getField(row, cols, field);
        return val != null ? val : defaultValue;
    }

    /** SHA-256 hex of the raw bytes — the media pipeline's dedup key + idempotency fingerprint. */
    private static String sha256Hex(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String generateSku(String title) {
        if (title == null) return "PROD-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String base = title.toUpperCase()
                .replaceAll("[^A-Z0-9]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (base.length() > 15) base = base.substring(0, 15);
        return base + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }
}
