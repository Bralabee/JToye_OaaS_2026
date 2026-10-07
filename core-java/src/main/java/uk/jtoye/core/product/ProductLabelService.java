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
import uk.jtoye.core.config.ClockConfig;
import uk.jtoye.core.exception.IncompleteLabelDataException;
import uk.jtoye.core.exception.InvalidProductionDateException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.product.IngredientMarkupParser.ParsedIngredients;
import uk.jtoye.core.product.LabelRenderModel.IngredientRun;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.io.ByteArrayOutputStream;
import java.time.Clock;
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
 *
 * <p>#861 (D-16, D-17): the label also prints the production date and counts the
 * durability date from it (not from the moment the PDF is downloaded), and prints a
 * separate "May contain: …" line for the product's cross-contact allergens that are
 * not already declared. Every date is rendered en-GB with the full month name.
 */
@Service
@Transactional(readOnly = true)
public class ProductLabelService {

    /**
     * UK label date format with the full month name, e.g. "5 October 2026" (D-17). The full
     * name is unambiguous to every reader, and avoids the CLDR short form "Sept".
     */
    static final DateTimeFormatter LABEL_DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);

    private final ProductRepository productRepository;
    private final ShopRepository shopRepository;
    private final ShopAccessService shopAccessService;
    private final Clock clock;

    public ProductLabelService(ProductRepository productRepository, ShopRepository shopRepository,
                               ShopAccessService shopAccessService, Clock clock) {
        this.productRepository = productRepository;
        this.shopRepository = shopRepository;
        this.shopAccessService = shopAccessService;
        this.clock = clock;
    }

    /**
     * Generate the PPDS label PDF for a product produced today (UK time). Equivalent to
     * {@code generateLabel(productId, null)}.
     */
    public byte[] generateLabel(UUID productId) {
        return generateLabel(productId, null);
    }

    /**
     * Generate the PPDS label PDF for a product.
     *
     * @param productionDate the date the food was produced (D-17), or {@code null} for today in
     *                       Europe/London. "Today" is read from the injected clock once per call,
     *                       so concurrent downloads either side of UK midnight each get their own
     *                       date; no date state is shared between calls.
     * @throws ResourceNotFoundException      if the product does not exist (tenant-scoped, 404)
     * @throws IncompleteLabelDataException   if the product is missing required PPDS
     *                                        data — business identity (null/blank shop_id,
     *                                        or a shop_id that resolves to no tenant-owned
     *                                        shop), business address, shelf life, or
     *                                        durability type (422)
     * @throws InvalidProductionDateException if the production date is after today (UK time),
     *                                        or the durability date it gives has already
     *                                        passed (422, field {@code productionDate})
     */
    public byte[] generateLabel(UUID productId, LocalDate productionDate) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        // VSA-02 (D-02): the label endpoint (/products/{id}/label) is a shop-scoped
        // read — require at least STAFF on the product's owning shop (parent-lookup),
        // so a cross-shop label pull yields the typed shop 403, not another shop's PDF.
        //
        // WR-08 null-shop READ half (plan 23-10): a shop_id IS NULL product is a
        // tenant-wide / legacy resource readable by any granted scoped user, so skip the
        // gate here to match the getProductById read route. Such a product still fails PPDS
        // validation below with a 422 (no business identity — it has no shop to source a
        // name/address from), NEVER a 403; a null-shop product simply cannot be labelled.
        if (product.getShopId() != null) {
            shopAccessService.require(product.getShopId(), ShopRole.STAFF);
        }

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

        // D-17 / Pitfall 17: "today" is the UK date, resolved once for this request from the
        // injected clock. withZone makes a clock supplied in any other zone (a UTC container, a
        // fixed UTC test clock) still yield the London date: at 00:30 BST it is already tomorrow.
        LocalDate today = LocalDate.now(clock.withZone(ClockConfig.UK_ZONE));
        LocalDate produced = productionDate != null ? productionDate : today;
        validateProductionDate(product, produced, today);

        LabelRenderModel model = buildRenderModel(product, shop, produced);
        return renderPdf(model);
    }

    /**
     * D-17: refuse a production date that cannot be true for food being labelled now — one
     * after today (UK time), or one whose durability date (production date + shelf life) is
     * already before today. A use-by of today is still printable. Called after
     * {@link #validatePpdsData}, so shelf life and durability type are present.
     */
    private static void validateProductionDate(Product product, LocalDate produced, LocalDate today) {
        if (produced.isAfter(today)) {
            throw new InvalidProductionDateException(
                    "productionDate " + produced.format(LABEL_DATE) + " is after today ("
                            + today.format(LABEL_DATE) + ", UK time): a label cannot be dated "
                            + "from food that has not been made yet");
        }
        LocalDate durability = produced.plusDays(product.getShelfLifeDays());
        if (durability.isBefore(today)) {
            throw new InvalidProductionDateException(
                    "productionDate " + produced.format(LABEL_DATE) + " gives a "
                            + durabilityWord(product) + " date of " + durability.format(LABEL_DATE)
                            + ", which has already passed (today is " + today.format(LABEL_DATE)
                            + ", UK time)");
        }
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
     * {@code productionDate} is INJECTABLE so the production and durability dates
     * are byte-stable for a fixed date (the AC3 golden test relies on this).
     *
     * <p>Ingredient runs come from a render-time RE-PARSE of {@code ingredientsText}
     * via {@link IngredientMarkupParser} (authoritative), NOT the stored
     * {@code allergen_spans} cache, so an edited text can never render stale
     * emphasis.
     *
     * <p>#861 (D-16): the may-contain names are the product's cross-contact allergens that
     * are not already declared ({@link MayContainAllergens}), in catalogue bit order. Neither
     * mask is changed; the declared emphasis in the ingredients is untouched by them.
     *
     * <p>Callers MUST have validated required PPDS data first (see
     * {@link #validatePpdsData}); this method assumes a non-null shop with a
     * durability type + shelf life.
     */
    static LabelRenderModel buildRenderModel(Product product, Shop shop, LocalDate productionDate) {
        ParsedIngredients parsed = IngredientMarkupParser.parse(product.getIngredientsText());
        List<IngredientRun> runs = toRuns(parsed);
        List<String> mayContainNames =
                MayContainAllergens.undeclaredNames(product.getMayContainMask(), product.getAllergenMask());
        String mayContainLine = mayContainNames.isEmpty()
                ? null
                : "May contain: " + String.join(", ", mayContainNames);
        return new LabelRenderModel(
                product.getTitle(),
                product.getSku(),
                product.getPricePennies(),
                runs,
                mayContainNames,
                mayContainLine,
                productionDate,
                "Produced: " + productionDate.format(LABEL_DATE),
                durabilityLine(product, productionDate),
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

    private static String durabilityLine(Product product, LocalDate productionDate) {
        LocalDate date = productionDate.plusDays(product.getShelfLifeDays());
        String label = "BEST_BEFORE".equals(product.getDurabilityType()) ? "Best before: " : "Use by: ";
        return label + date.format(LABEL_DATE);
    }

    private static String durabilityWord(Product product) {
        return "BEST_BEFORE".equals(product.getDurabilityType()) ? "best-before" : "use-by";
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

        // Ingredients with allergens emphasised INLINE (FSA requirement). #861: the heading runs
        // into the list on the same line (it was a line of its own), which frees the height the
        // May contain and Produced lines need on the 100x60mm label (one page, asserted by
        // ProductLabelServiceTest).
        Paragraph ingredients = new Paragraph();
        ingredients.add(new Chunk("Ingredients: ", sectionFont));
        for (IngredientRun run : model.ingredientRuns()) {
            ingredients.add(new Chunk(run.text(), run.emphasised() ? boldFont : bodyFont));
        }
        ingredients.setSpacingAfter(model.mayContainLine() == null ? 5 : 1);
        doc.add(ingredients);

        // #861 (D-16): cross-contact allergens, their own line beneath the ingredients, only when
        // at least one is not already declared. Never part of the emphasised ingredients.
        if (model.mayContainLine() != null) {
            Paragraph mayContain = new Paragraph(model.mayContainLine(), boldFont);
            mayContain.setSpacingAfter(3);
            doc.add(mayContain);
        }

        // Production date and the durability date counted from it (FSA: use-by / best-before;
        // D-17). One line at a 12pt leading, so the extra date does not push the business
        // identity off the 100x60mm label (asserted one page by ProductLabelServiceTest).
        Paragraph dates = new Paragraph(12);
        dates.add(new Chunk(model.productionLine(), bodyFont));
        dates.add(new Chunk("    ", bodyFont));
        dates.add(new Chunk(model.durabilityLine(), sectionFont));
        doc.add(dates);

        // Food business identity (FSA: business name + address).
        Paragraph business = new Paragraph(model.businessName(), bodyFont);
        business.setSpacingBefore(3);
        doc.add(business);
        doc.add(new Paragraph(model.businessAddress(), bodyFont));

        doc.close();
        return out.toByteArray();
    }
}
