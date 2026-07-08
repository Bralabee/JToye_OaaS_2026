package uk.jtoye.core.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import uk.jtoye.core.shop.Shop;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC3 golden-file compliance test for the PPDS (Natasha's Law) label render model.
 *
 * <p>Serializes {@link ProductLabelService#buildRenderModel} output for a FIXED
 * compliant fixture at a FIXED {@code generationDate} and asserts recursive
 * equality with a committed golden JSON. Because {@code buildRenderModel} is a
 * pure, package-visible method, this test needs NO Testcontainers/Spring (unlike
 * the finance golden test) — it lives in {@code uk.jtoye.core.product} so it can
 * call {@code buildRenderModel} directly.
 *
 * <p><b>FSA PPDS guidance the golden was reviewed against.</b> A Prepacked for
 * Direct Sale (PPDS) food label must carry, per the UK FSA PPDS guidance
 * (Food Information Regulations 2014 as amended by "Natasha's Law", in force
 * 1 Oct 2021):
 * <ol>
 *   <li><b>The name of the food</b> — asserted by {@code productName}.</li>
 *   <li><b>A full ingredients list with the 14 regulated allergens emphasised
 *       WITHIN the list</b> (e.g. bold/CAPS/underline) — NOT a separate
 *       "contains" statement. Asserted by the interleaved {@code ingredientRuns}
 *       with an {@code emphasised} flag on the allergen run and NO standalone
 *       allergen-summary block.</li>
 *   <li><b>A durability (use-by / best-before) date</b> — asserted by
 *       {@code durabilityLine}.</li>
 *   <li><b>The food business operator's name and address</b> — asserted by
 *       {@code businessName} + {@code businessAddress}.</li>
 * </ol>
 * Any regression that reintroduces a standalone allergen block, drops the
 * durability date, or omits the business identity changes this render model and
 * fails the recursive-equality assertion.
 *
 * <p>Bootstrap: {@link #captureGoldenOnce()} is {@code @Disabled} during normal
 * runs. To regenerate, temporarily remove {@code @Disabled}, run that single
 * method, commit the regenerated JSON, then restore {@code @Disabled}.
 */
class ProductLabelGoldenFileTest {

    private static final Path GOLDEN_RELATIVE =
            Paths.get("src", "test", "resources", "fixtures", "ppds-label-compliant.golden.json");

    /** Fixed generation date so "Use by: 8 Jul 2026" (= GEN + 3 days) is byte-stable. */
    private static final LocalDate GEN = LocalDate.of(2026, 7, 5);

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }

    private static Product fixtureProduct() {
        Product product = new Product();
        setField(product, "id", UUID.fromString("00000000-0000-0000-0000-000000082006"));
        product.setSku("YAM-500");
        product.setTitle("Yam Pottage 500g");
        product.setIngredientsText("Wheat flour, **milk**, sugar");
        product.setAllergenMask(0);
        product.setPricePennies(599L);
        product.setShelfLifeDays(3);
        product.setDurabilityType("USE_BY");
        product.setShopId(UUID.fromString("00000000-0000-0000-0000-000000082099"));
        return product;
    }

    private static Shop fixtureShop() {
        Shop shop = new Shop();
        shop.setName("Test Kitchen Ltd");
        shop.setAddress("12 Market Street, London, E1 6AN");
        return shop;
    }

    private static ObjectMapper objectMapper() {
        return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    private Path locateGolden() {
        // Gradle runs :core-java:test with the module dir as CWD, so src/test/...
        // resolves directly. Fall back to the module-scoped path for other CWDs.
        // Use parent-directory existence so capture-mode (pre-file-write) still
        // picks the correct path.
        Path relative = GOLDEN_RELATIVE;
        if (Files.isDirectory(relative.getParent())) {
            return relative;
        }
        return Paths.get("core-java").resolve(relative);
    }

    @Test
    void renderModelMatchesCommittedGolden() throws Exception {
        LabelRenderModel actual = ProductLabelService.buildRenderModel(
                fixtureProduct(), fixtureShop(), GEN);

        Path golden = locateGolden();
        assertThat(Files.exists(golden))
                .as("Golden file exists at %s — run captureGoldenOnce bootstrap to regenerate", golden)
                .isTrue();

        LabelRenderModel expected =
                objectMapper().readValue(Files.readString(golden), LabelRenderModel.class);

        assertThat(actual).usingRecursiveComparison().isEqualTo(expected);
    }

    /**
     * One-shot bootstrap. Re-enable by removing {@code @Disabled} temporarily, run
     * this single test, commit the regenerated
     * {@code core-java/src/test/resources/fixtures/ppds-label-compliant.golden.json},
     * then restore {@code @Disabled}.
     */
    @Test
    @Disabled("One-shot bootstrap — re-enable manually to regenerate the golden file, then re-disable.")
    void captureGoldenOnce() throws Exception {
        LabelRenderModel model = ProductLabelService.buildRenderModel(
                fixtureProduct(), fixtureShop(), GEN);
        Path golden = locateGolden();
        Files.createDirectories(golden.getParent());
        objectMapper().writerWithDefaultPrettyPrinter().writeValue(golden.toFile(), model);
    }
}
