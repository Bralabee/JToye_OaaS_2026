package uk.jtoye.core.product;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.IncompleteLabelDataException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.product.IngredientMarkupParser.ParsedIngredients;
import uk.jtoye.core.product.LabelRenderModel.IngredientRun;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Generates FSA-compliant PPDS (Natasha's Law) allergen labels.
 *
 * <p>The compliant format emphasises allergens INLINE within the ingredients list
 * (no standalone allergen-summary block), prints a computed durability date
 * ('Use by' / 'Best before'), and prints the food business name + address. When
 * the product is missing any of that required data, generation throws
 * {@link IncompleteLabelDataException} (HTTP 422) naming the missing field(s)
 * rather than emitting a misleading, non-compliant label.
 */
@Service
@Transactional(readOnly = true)
public class ProductLabelService {

    /** UK durability date format, e.g. "8 Jul 2026". */
    private static final DateTimeFormatter DURABILITY_DATE =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK);

    private final ProductRepository productRepository;
    private final ShopRepository shopRepository;
    private final ShopAccessService shopAccessService;

    public ProductLabelService(ProductRepository productRepository, ShopRepository shopRepository,
                               ShopAccessService shopAccessService) {
        this.productRepository = productRepository;
        this.shopRepository = shopRepository;
        this.shopAccessService = shopAccessService;
    }

    /**
     * Generate the PPDS label PDF for a product.
     *
     * @throws ResourceNotFoundException    if the product does not exist (tenant-scoped, 404)
     * @throws IncompleteLabelDataException if the product is missing required PPDS
     *                                      data — business identity (null/blank shop_id,
     *                                      or a shop_id that resolves to no tenant-owned
     *                                      shop), business address, shelf life, or
     *                                      durability type (422)
     */
    public byte[] generateLabel(UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        // VSA-02 (D-02): the label endpoint (/products/{id}/label) is a shop-scoped
        // read — require at least STAFF on the product's owning shop (parent-lookup),
        // so a cross-shop label pull yields the typed shop 403, not another shop's PDF.
        shopAccessService.require(product.getShopId(), ShopRole.STAFF);

        // Resolve the owning shop tenant-safely. findByIdAndTenantId (NOT plain
        // findById) avoids the shops_public_read RLS cross-tenant leak (T-ovt-01).
        // The Optional CAN be empty for a NON-NULL shop_id (shop_id is ON DELETE
        // SET NULL and a client-supplied shopId is not tenant-validated on write —
        // FK checks bypass RLS), so we use .orElse(null) and treat null/empty
        // IDENTICALLY to a missing business identity -> 422, never a 500.
        Shop shop = null;
        if (product.getShopId() != null) {
            UUID tenantId = TenantContext.get()
                    .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
            shop = shopRepository.findByIdAndTenantId(product.getShopId(), tenantId).orElse(null);
        }

        validatePpdsData(product, shop);

        LabelRenderModel model = buildRenderModel(product, shop, LocalDate.now());
        return renderPdf(model);
    }

    /**
     * Collect and report EVERY missing required PPDS field at once, so the vendor
     * sees the full list rather than fixing them one 422 at a time.
     */
    private void validatePpdsData(Product product, Shop shop) {
        List<String> missing = new ArrayList<>();
        if (shop == null || shop.getName() == null || shop.getName().isBlank()) {
            missing.add("business identity (shop name)");
        }
        if (shop == null || shop.getAddress() == null || shop.getAddress().isBlank()) {
            missing.add("business address");
        }
        if (product.getShelfLifeDays() == null) {
            missing.add("shelf life (shelf_life_days)");
        }
        if (product.getDurabilityType() == null || product.getDurabilityType().isBlank()) {
            missing.add("durability type (durability_type)");
        }
        if (!missing.isEmpty()) {
            throw new IncompleteLabelDataException(
                    "Cannot generate PPDS label for product " + product.getId()
                            + ": missing " + String.join(", ", missing));
        }
    }

    /**
     * Pure, deterministic render-model builder. No repository/PDF I/O; the
     * {@code generationDate} is INJECTABLE so the durability date is byte-stable
     * for a fixed date (the AC3 golden test relies on this).
     *
     * <p>Ingredient runs come from a render-time RE-PARSE of {@code ingredientsText}
     * via {@link IngredientMarkupParser} (authoritative), NOT the stored
     * {@code allergen_spans} cache, so an edited text can never render stale
     * emphasis.
     *
     * <p>Callers MUST have validated required PPDS data first (see
     * {@link #validatePpdsData}); this method assumes a non-null shop with a
     * durability type + shelf life.
     */
    static LabelRenderModel buildRenderModel(Product product, Shop shop, LocalDate generationDate) {
        ParsedIngredients parsed = IngredientMarkupParser.parse(product.getIngredientsText());
        List<IngredientRun> runs = toRuns(parsed);
        String durabilityLine = durabilityLine(product, generationDate);
        return new LabelRenderModel(
                product.getTitle(),
                product.getSku(),
                product.getPricePennies(),
                runs,
                durabilityLine,
                shop.getName(),
                shop.getAddress());
    }

    /**
     * Interleave non-emphasised segments and emphasised (allergen) spans into an
     * ordered list of runs covering the whole plainText.
     */
    private static List<IngredientRun> toRuns(ParsedIngredients parsed) {
        String plain = parsed.plainText();
        List<IngredientRun> runs = new ArrayList<>();
        int cursor = 0;
        for (AllergenSpan span : parsed.spans()) {
            if (span.start() > cursor) {
                runs.add(new IngredientRun(plain.substring(cursor, span.start()), false));
            }
            runs.add(new IngredientRun(plain.substring(span.start(), span.end()), true));
            cursor = span.end();
        }
        if (cursor < plain.length()) {
            runs.add(new IngredientRun(plain.substring(cursor), false));
        }
        return runs;
    }

    private static String durabilityLine(Product product, LocalDate generationDate) {
        LocalDate date = generationDate.plusDays(product.getShelfLifeDays());
        String label = "BEST_BEFORE".equals(product.getDurabilityType()) ? "Best before: " : "Use by: ";
        return label + date.format(DURABILITY_DATE);
    }

    /**
     * Thin OpenPDF renderer. Builds the ingredients as ONE flowing Paragraph of
     * Chunks — emphasised runs in bold — so allergens are emboldened INLINE within
     * the list. No standalone allergen-summary block, no fallback text.
     */
    private byte[] renderPdf(LabelRenderModel model) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Label size: 100mm x 60mm (283 x 170 pt).
        Document doc = new Document(new Rectangle(283, 170), 10, 10, 10, 10);
        PdfWriter.getInstance(doc, out);
        doc.open();

        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
        Font skuFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Font.ITALIC);
        Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
        Font bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 8);
        Font boldFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
        Font priceFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);

        // Product name (FSA: name of the food).
        Paragraph title = new Paragraph(model.productName(), titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        doc.add(title);

        // SKU.
        Paragraph sku = new Paragraph(model.sku(), skuFont);
        sku.setAlignment(Element.ALIGN_CENTER);
        sku.setSpacingAfter(5);
        doc.add(sku);

        // Price (optional).
        if (model.pricePennies() != null) {
            Paragraph price = new Paragraph(
                    String.format("£%.2f", model.pricePennies() / 100.0), priceFont);
            price.setAlignment(Element.ALIGN_CENTER);
            price.setSpacingAfter(5);
            doc.add(price);
        }

        // Ingredients with allergens emphasised INLINE (FSA requirement).
        doc.add(new Paragraph("Ingredients:", sectionFont));
        Paragraph ingredients = new Paragraph();
        for (IngredientRun run : model.ingredientRuns()) {
            ingredients.add(new Chunk(run.text(), run.emphasised() ? boldFont : bodyFont));
        }
        ingredients.setSpacingAfter(5);
        doc.add(ingredients);

        // Durability date (FSA: use-by / best-before).
        doc.add(new Paragraph(model.durabilityLine(), sectionFont));

        // Food business identity (FSA: business name + address).
        Paragraph business = new Paragraph(model.businessName(), bodyFont);
        business.setSpacingBefore(3);
        doc.add(business);
        doc.add(new Paragraph(model.businessAddress(), bodyFont));

        doc.close();
        return out.toByteArray();
    }
}
