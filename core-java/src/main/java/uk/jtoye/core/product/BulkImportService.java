package uk.jtoye.core.product;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import uk.jtoye.core.ai.ImageAnalysisResult;
import uk.jtoye.core.ai.ImageAnalysisService;
import uk.jtoye.core.exception.ShopAccessDeniedException;
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
import java.util.*;

@Service
@Transactional
public class BulkImportService {
    private static final Logger log = LoggerFactory.getLogger(BulkImportService.class);

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;
    private final ImageAnalysisService imageAnalysisService;
    private final StorageService storageService;
    private final ShopAccessService shopAccessService;

    public BulkImportService(ProductRepository productRepository, ProductMapper productMapper,
                              ImageAnalysisService imageAnalysisService, StorageService storageService,
                              ShopAccessService shopAccessService) {
        this.productRepository = productRepository;
        this.productMapper = productMapper;
        this.imageAnalysisService = imageAnalysisService;
        this.storageService = storageService;
        this.shopAccessService = shopAccessService;
    }

    /**
     * Generate a CSV template with headers and an example row.
     */
    public String generateCsvTemplate() {
        // shop_id is optional but recommended (Phase 23, VSA-02): each row may target a
        // specific shop the caller manages. A scoped (non-GROUP_ADMIN) importer MUST
        // supply a shop_id they hold SHOP_MANAGER on for every row; a GROUP_ADMIN may
        // omit it (product stays unassigned, legacy behaviour).
        return """
                title,sku,price_pounds,ingredients,category,description,dietary_tags,prep_time_minutes,allergen_mask,shop_id
                Jollof Rice,JOLLOF-001,8.99,"Rice, tomatoes, peppers, onions, vegetable oil, seasoning",Mains,Our signature smoky jollof rice slow-cooked to perfection,"Halal, Gluten-Free",20,0,
                Puff Puff,PUFF-001,2.50,"Flour, sugar, yeast, water, nutmeg",Snacks,Fluffy Nigerian doughnut balls lightly dusted with sugar,Vegetarian,10,1,
                """;
    }

    /**
     * Parse and import products from a CSV file.
     * Validates each row — creates valid products, collects errors for invalid rows.
     */
    @CacheEvict(value = "products", allEntries = true)
    public BulkImportResult importFromCsv(MultipartFile file) {
        UUID tenantId = TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));

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

        // §3-FLAG #1 (VSA-02): resolve the caller's scope ONCE. A GROUP_ADMIN may import
        // rows with or without a shop_id; a scoped user must own SHOP_MANAGER on every
        // row's shop_id. Deny-by-default.
        boolean groupAdmin = shopAccessService.isGroupAdmin();

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

            // §3-FLAG #1: per-row shop-access gate, OUTSIDE the per-row try/catch so an
            // ungranted row fails the WHOLE batch with the typed 403 (deny-by-default,
            // no partial apply — the surrounding @Transactional rolls back every prior
            // row's save). A row with a shop_id demands SHOP_MANAGER on it; a row with
            // no shop_id is allowed only for a GROUP_ADMIN (else denied).
            if (rowShopId != null) {
                shopAccessService.require(rowShopId, ShopRole.SHOP_MANAGER);
            } else if (!groupAdmin) {
                throw new ShopAccessDeniedException(null, ShopRole.SHOP_MANAGER);
            }

            try {
                Product product = parseRow(row, columnIndex, tenantId, rowNum, result);
                if (product != null) {
                    if (rowShopId != null) {
                        product.setShopId(rowShopId);
                    }
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
     */
    @CacheEvict(value = "products", allEntries = true)
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

                // Upload the image to storage
                String imageUrl = storageService.upload(tenantId, "products", product.getId(), file);
                product.setImageUrl(imageUrl);
                product = productRepository.saveAndFlush(product);

                result.getCreated().add(productMapper.toDto(product));

                log.info("AI import: created '{}' from image {} (confidence: {})",
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
