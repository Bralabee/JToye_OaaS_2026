package uk.jtoye.core.product;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.jtoye.core.product.LabelRenderModel.IngredientRun;
import uk.jtoye.core.shop.Shop;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ProductLabelService}. This minimal version (Task 3, TDD)
 * covers the pure {@code buildRenderModel} happy path; Task 4 expands it with
 * mock-wired {@code generateLabel}, PDF-text negative asserts, and fail-loud
 * coverage.
 */
class ProductLabelServiceTest {

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }

    private static Product compliantProduct() {
        Product product = new Product();
        setField(product, "id", UUID.randomUUID());
        product.setSku("YAM-500");
        product.setTitle("Yam Pottage 500g");
        product.setIngredientsText("Wheat flour, **milk**, sugar");
        product.setAllergenMask(0);
        product.setPricePennies(599L);
        product.setShelfLifeDays(3);
        product.setDurabilityType("USE_BY");
        product.setShopId(UUID.randomUUID());
        return product;
    }

    private static Shop compliantShop() {
        Shop shop = new Shop();
        shop.setName("Test Kitchen Ltd");
        shop.setAddress("12 Market Street, London, E1 6AN");
        return shop;
    }

    @Test
    @DisplayName("buildRenderModel - emits an inline emphasised 'milk' run and a fixed durability line")
    void buildRenderModelHappyPath() {
        LabelRenderModel model = ProductLabelService.buildRenderModel(
                compliantProduct(), compliantShop(), LocalDate.of(2026, 7, 5));

        // An emphasised run over exactly "milk" exists.
        assertThat(model.ingredientRuns())
                .anySatisfy(run -> {
                    assertThat(run.text()).isEqualTo("milk");
                    assertThat(run.emphasised()).isTrue();
                });
        // Non-emphasised neighbours are present and flat text reconstructs the plain list.
        assertThat(model.ingredientRuns())
                .filteredOn(run -> !run.emphasised())
                .extracting(IngredientRun::text)
                .contains("Wheat flour, ", ", sugar");

        // Deterministic durability line: 2026-07-05 + 3 days -> 8 Jul 2026.
        assertThat(model.durabilityLine()).isEqualTo("Use by: 8 Jul 2026");
        assertThat(model.businessName()).isEqualTo("Test Kitchen Ltd");
        assertThat(model.businessAddress()).isEqualTo("12 Market Street, London, E1 6AN");
    }
}
